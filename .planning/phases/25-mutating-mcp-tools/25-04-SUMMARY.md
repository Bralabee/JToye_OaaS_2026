---
phase: 25-mutating-mcp-tools
plan: 04
subsystem: docs-gate + live-e2e
tags: [docs-freshness, openapi-snapshot, keycloak, realm-reimport, client-credentials, mcp, idempotency, force-rls, vsa-02, ai-02]
requires:
  - "25-01 orders:write / customers:write @PreAuthorize gates + ScopedWriteAccessIntegrationTest + OpenApiConfig scope-doc change"
  - "25-02 integration-orders-rw realm client + INTEGRATION_ORDERS_RW_SECRET wiring + ACCESS_MACHINE_CLIENT_IDS allowlist"
  - "25-03 create_order/create_customer MCP write tools + corePost + CrossTenantMcpWriteRlsIntegrationTest (2 new vitest files)"
provides:
  - "docs/metrics.json reconciled to the --write arbiter (total 1648 -> 1675; mcp 27/6 -> 47/8; java 1128/199 -> 1135/201); docs-freshness check-mode EXIT 0"
  - "write-surface docs consistent with the shipped surface (security-scopes.md enforced-list + RW client + write recipe; mcp-server/README.md write-allowed flip + through-MCP recipe; idempotency.md MCP-mandates-the-key note)"
  - "OpenApiSnapshotTest GREEN (snapshot regenerated for the 25-01 scope-doc drift: orders:write/customers:write enforced + customers:read added)"
  - "human-approved live E2E on the rebuilt dev stack: create 200 / idempotent-replay-no-dup / no-scope 403 / cross-tenant 404-RLS / no rogue shop_staff GROUP_ADMIN row (VSA-02)"
affects:
  - "phase-25 close (AI-02 complete); orchestrator secure-phase / verify-work / PR next"
tech-stack:
  added: []
  patterns:
    - "docs-freshness.sh --write is the count arbiter; check-mode must then EXIT 0; prose (CLAUDE.md/AGENTS.md) synced to the arbiter total by hand"
    - "OpenApiConfig scope-doc change -> updateOpenApiSnapshot regen (oasdiff-gated OpenApiSnapshotTest); regen only when the snapshot actually drifted"
    - "live realm re-import: envsubst re-render -> kc.sh import --override true (Postgres-backed) -> restart Keycloak (running server caches the realm)"
    - "self-sufficient RW machine credential (orders:write+customers:write+catalog:read) drives create -> idempotent-replay -> cross-tenant-404 -> no-scope-403 without a second token"
key-files:
  created:
    - .planning/phases/25-mutating-mcp-tools/25-04-SUMMARY.md
  modified:
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - docs/security-scopes.md
    - docs/idempotency.md
    - mcp-server/README.md
    - docs/api/openapi-snapshot.json
    - infra/keycloak/realm-export.template.json
key-decisions:
  - "OpenAPI snapshot WAS regenerated: the 25-01 OpenApiConfig change diffed both the info.description scopes list and the catalog-scopes securityScheme Scopes() block (committed snapshot still read 'reserved', no customers:*) -> OpenApiSnapshotTest was RED, now GREEN"
  - "The RW client description >255 chars was a genuine deployment blocker (kc.sh import 22P01) fixed under Rule 1/3 (prior-wave defect blocking the 25-04 live E2E), TEMPLATE-only"
  - "probe-4 replay updatedAt UTC-vs-local caveat ACCEPTED by the user as a pre-existing core idempotency-store serialization detail (same instant, same customer, zero duplicates); D-06 locked no core idempotency change -> NOT a Phase-25 regression and NOT a new backlog item"
patterns-established:
  - "Live re-import recipe when jtoye-dev already exists: re-render -> kc.sh import --override true -> docker restart jtoye-keycloak (import writes the shared DB out-of-band; the running server needs a restart to load it)"
requirements-completed: [AI-02]
duration: ~35min
completed: 2026-07-24
---

# Phase 25 Plan 04: Docs-Freshness + OpenAPI Reconcile + Human-Approved Live Write E2E Summary

**Reconciled the docs/count + OpenAPI gates to the shipped write surface (total 1648->1675, snapshot regenerated GREEN) and proved the whole mutating-MCP flow LIVE on the rebuilt dev stack — create 200 / idempotent-replay-no-duplicate / no-scope 403 / cross-tenant 404-RLS / no rogue shop_staff row — human-approved, closing AI-02.**

## Performance

- **Duration:** ~35 min active
- **Started:** 2026-07-24T16:33:46Z
- **Completed:** 2026-07-24
- **Tasks:** 2 (Task 1 autonomous; Task 2 blocking human-verify — approved)
- **Files modified:** 8 (7 in Task 1 + 1 auto-fix in Task 2)

## Accomplishments

- **docs-freshness reconciled** via `scripts/docs-freshness.sh --write` (the arbiter): `mcp_test_blocks` 27->47, `mcp_test_files` 6->8, `java_test_methods` 1128->1135, `java_test_files` 199->201, `total_logical_invocations` **1648->1675**; check-mode then **EXIT 0**. `CLAUDE.md` + `AGENTS.md` "logical invocations" prose synced to the arbiter total + per-family breakdown.
- **Write-surface docs refreshed** to match the shipped surface: `security-scopes.md` (orders:write/customers:write enforced in the taxonomy + write-gate model; `integration-orders-rw` reference client + `INTEGRATION_ORDERS_RW_SECRET` wiring; write-E2E recipe; SS6 mandate marked discharged), `mcp-server/README.md` (flipped "write is denied 403" -> "write allowed under the RW credential"; added `create_order`/`create_customer` to the tool table + the through-MCP write recipe), `idempotency.md` (MCP write tools MANDATE the `Idempotency-Key`, D-05; no core change, D-06).
- **OpenAPI snapshot regenerated GREEN** — the 25-01 `OpenApiConfig` doc change had drifted the committed snapshot (both `info.description` and the `catalog-scopes` `Scopes()` block still read "reserved" with no `customers:*`); `./gradlew :core-java:updateOpenApiSnapshot` rewrote it and `OpenApiSnapshotTest` (check-mode) is BUILD SUCCESSFUL.
- **Live write E2E on the rebuilt dev stack, human-approved** — full create -> idempotent-replay -> cross-tenant-404 -> no-scope-403 flow proven with no runtime-state pollution (VSA-02).

## Task Commits

1. **Task 1: Reconcile docs/metrics.json + write-surface docs + OpenAPI snapshot** - `3503d4e` (docs)
2. **Task 2 auto-fix: shorten integration-orders-rw client description to <=255 chars** - `f4d817f` (fix) — surfaced while driving Task 2's live re-import
3. **Task 2: Live E2E** — no source commit (live runtime verification); evidence recorded below, human-approved

**Plan metadata:** this commit (`docs(25-04): ...` — SUMMARY + STATE + ROADMAP + REQUIREMENTS)

## Files Created/Modified

- `docs/metrics.json` - reconciled counts (the `--write` arbiter output)
- `CLAUDE.md` / `AGENTS.md` - "logical invocations" prose synced to 1675 (1135/201 java, 47/8 mcp)
- `docs/security-scopes.md` - orders:write/customers:write enforced; RW reference client + secret wiring + write recipe; SS4 fail-closed + SS6 mandate updated
- `mcp-server/README.md` - write-allowed flip; write tools in the table; through-MCP write/replay/cross-tenant/no-scope recipe
- `docs/idempotency.md` - MCP write tools mandate the key (D-05); no core change (D-06)
- `docs/api/openapi-snapshot.json` - regenerated for the 25-01 scope-doc change
- `infra/keycloak/realm-export.template.json` - RW client description trimmed 339->244 chars (Rule 1/3 fix)

## Live E2E Evidence (statuses + assertions only — no tokens, no PII)

Canonical Compose runtime (`docker-compose.full-stack.yml`; RULE 0 compose-XOR-local-k8s). `INTEGRATION_ORDERS_RW_SECRET` added to `.env` (gitignored) -> `scripts/verify-env.sh` PASS (16/16 non-weak) -> **rebuilt ALL** (core-java + mcp-server images, both `healthy`) -> realm re-rendered -> `kc.sh import --override true` -> `docker restart jtoye-keycloak` (healthy). `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` is hardcoded on the core-java service (25-02), so no `.env` entry was required.

| # | Probe | Observed | Verdict |
|---|-------|----------|---------|
| 1 | env preflight | `verify-env.sh` 16/16 non-weak | PASS |
| 2 | rebuild ALL + realm re-import | core-java + mcp-server healthy; realm imported + KC restarted healthy | PASS |
| 3 | RW token claims | `scope="orders:write customers:write catalog:read"`, `aud` includes `core-api`, `tenant_id=…0001`, `azp=integration-orders-rw`, UUID sub | PASS |
| 4 | `create_customer` + replay | first -> 200 + CustomerDto; **replay same key -> customers row count stays 1 (no duplicate), same customer id** | PASS (see caveat) |
| 5 | `create_order` (RW) | 200 + OrderDto, `status=DRAFT`, shopId match | PASS |
| 6 | `create_order` (no-scope RO token) | `isError:true` -> `"core 403 Forbidden: Access denied"` (sanitized; no stack/PII) | PASS |
| 7 | `create_order` (RW -> tenant-B shopId) | `isError:true` -> `"core 404 … Shop not found or does not belong to your tenant"` (FORCE RLS) | PASS |
| 8 | VSA-02 `shop_staff` | GROUP_ADMIN rows `0` (baseline `0`); total `shop_staff` `0` — no rogue opaque-UUID row for the RW SA. Counted as the superuser `jtoye` role (rolbypassrls=t), so the count is authoritative across tenants | PASS |

**tools/list** on the rebuilt MCP returned all five tools (`create_order`, `create_customer`, `list_products`, `list_shops`, `read_orders`) — the write tools are registered.

### Accepted caveat — probe-4 replay `updatedAt` rendering (user-approved close)

The same-key replay returns the **same customer** (identical `id` + every field) with **zero duplicate rows** — the security-relevant AC-1 dedup guarantee holds. The only byte difference is `updatedAt`: the fresh create serialized it with the local offset (`2026-07-24T17:47:27.590945688+01:00`) while the replay returned the stored idempotency body normalized to UTC (`…16:47:27.590945688Z`) — **the same instant**. This is a pre-existing core idempotency-store serialization detail affecting all idempotent endpoints, not the MCP path; D-06 locked "no core idempotency change this phase". The user explicitly chose "Approve — close AI-02" (not file-a-ticket): this is **NOT** a Phase-25 regression and **NOT** a new backlog item. Recorded as accepted/known.

## Decisions Made

- **Regenerate the OpenAPI snapshot (not leave it):** the 25-01 `OpenApiConfig` change genuinely diffed the committed snapshot -> `OpenApiSnapshotTest` was RED -> `updateOpenApiSnapshot` (Testcontainers Spring boot) regenerated it GREEN. Confirmed the diff was scope-doc-only (`info.description` + `catalog-scopes` Scopes block).
- **Fix the RW-client description in the TEMPLATE only** (rendered `realm-export.json` is gitignored, regenerated by envsubst).
- **Accept the probe-4 `updatedAt` caveat** per explicit user direction; do not open a ticket.
- Left the milestone/phase percent + the ROADMAP top-level phase checkbox to the orchestrator's phase-close (anti-false-green): this plan marks plan-progress 4/4 and AI-02 complete on the human-approved live E2E; secure-phase / verify-work / PR are the orchestrator's next steps.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1/3 - Bug / Blocking] `integration-orders-rw` client description exceeded Keycloak's varying(255) column, bricking the realm re-import**
- **Found during:** Task 2 (live E2E — the `kc.sh import --override true` step)
- **Issue:** 25-02's RW client `description` was 339 chars; `kc.sh import` aborted with `SQLSTATE 22001 "value too long for type character varying(255)"` on `UPDATE CLIENT` — a deployment blocker for any non-fresh realm (dev/staging/prod), invisible to the JSON-validation checks 25-02 ran. `integration-catalog-ro` (223 chars) was always under the limit.
- **Fix:** Trimmed the description to 244 chars in `infra/keycloak/realm-export.template.json`, meaning preserved (least-privilege orders:write+customers:write+catalog:read, NOT catalog:write; RLS walls cross-tenant).
- **Files modified:** infra/keycloak/realm-export.template.json (TEMPLATE only)
- **Verification:** re-render -> no client field >255 (jq sweep) -> `kc.sh import --override true` `Realm 'jtoye-dev' imported` (EXIT 0) -> KC restart healthy -> RW token mints with the expected scopes.
- **Committed in:** `f4d817f`

---

**Total deviations:** 1 auto-fixed (Rule 1/3 blocking). **Impact:** necessary to unblock the live E2E (and to make the realm deployable at all on a non-fresh IdP). No scope creep.

## Issues Encountered

- **Running containers predated Phase 25.** The full-stack was up from 16-44h ago (pre-25 core-java/mcp images; realm without the RW client). Resolved by the D-12 precondition: rebuild ALL + re-render + `kc.sh import --override true` + Keycloak restart.
- **Realm re-import 22P01** — see the Rule 1/3 auto-fix above.

## Threat Model Discharge

| Threat ID | Disposition | How discharged |
|-----------|-------------|-----------------|
| T-25-13 (stale/pre-re-import token retains write) | mitigate | Fail-closed re-import (D-12): tokens minted before the re-import lack the scopes; the no-scope `integration-catalog-ro` token is 403 on create (probe 6). |
| T-25-14 (RW SA accretes a JIT GROUP_ADMIN row) | mitigate | `ACCESS_MACHINE_CLIENT_IDS=integration-orders-rw` allowlist (25-02); probe 8 asserts 0 GROUP_ADMIN rows after a successful create_order (authoritative under the bypassrls role). |
| T-25-15 (docs drift from the shipped surface -> CI red / stale contract) | mitigate | `docs-freshness.sh --write` arbiter (check-mode EXIT 0) + `updateOpenApiSnapshot` regen (OpenApiSnapshotTest GREEN). |

## Requirements

**AI-02 -> COMPLETE.** All four contributing plans shipped and the last acceptance criterion (the live write E2E, D-12) is human-approved: core write-scope gates (25-01), the RW realm client + secret/allowlist wiring (25-02), the `create_order`/`create_customer` MCP write tools + cross-tenant RLS proof (25-03), and this plan's docs/OpenAPI reconcile + human-verified live E2E (25-04). AI-02 was held PENDING across 25-01..25-03 (anti-false-green); it closes now.

## Known Stubs

None. No placeholder/empty-value surfaces introduced; the docs describe the live-verified surface.

## User Setup Required

None for CI. For a local live re-run: set `INTEGRATION_ORDERS_RW_SECRET` in `.env` (gitignored), rebuild ALL, and `kc.sh import --override true` + restart Keycloak (recipe in `mcp-server/README.md` §5 / `docs/security-scopes.md` §4). Two harmless dev-DB test artifacts remain under tenant …0001 (2 demo customers + 1 DRAFT order) — left as-is.

## Next Phase Readiness

- Phase 25 plans 4/4 done; AI-02 complete. Ready for the orchestrator's secure-phase / verify-work / phase PR.
- Next milestone phase: 26 (Local-K8s Overlay + Verified Breakage Fixes, INFRA-01/02) — needs `/gsd:discuss-phase 26` / `/gsd:plan-phase 26`.

## Self-Check: PASSED

- File `25-04-SUMMARY.md` present on disk (FOUND).
- Commits present in history: `3503d4e` (Task 1 docs), `f4d817f` (Rule 1/3 realm fix), `96ac4a7` (plan metadata) — all FOUND.
- `scripts/docs-freshness.sh` check-mode EXIT 0 (total 1675); `OpenApiSnapshotTest` GREEN; live E2E human-approved.

---
*Phase: 25-mutating-mcp-tools*
*Completed: 2026-07-24*

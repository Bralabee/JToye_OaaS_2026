---
phase: 20
slug: ai-1-mcp-server-read-only-slice
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-13
---

# Phase 20 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution. Derived from `20-RESEARCH.md` § Validation Architecture. Task IDs are finalized by the planner; per-requirement behavior map below is authoritative.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | **vitest** (new, for `mcp-server/`) — ESM-native, avoids Jest/ESM friction with the ESM-only MCP SDK. Live E2E via shell scripts (curl/node) against the dev stack. |
| **Config file** | `mcp-server/vitest.config.ts` (Wave 0 — does not exist yet) |
| **Quick run command** | `cd mcp-server && npx vitest run` |
| **Full suite command** | `cd mcp-server && npx vitest run` (unit + integration) + `mcp-server/scripts/e2e.sh` + `mcp-server/scripts/e2e-rls.sh` (live) |
| **Estimated runtime** | unit/integration ~a few seconds; live E2E gated behind full container rebuild + realm re-import |

---

## Sampling Rate

- **After every task commit:** `cd mcp-server && npx vitest run` (unit + integration, mocked core)
- **After every plan wave:** full vitest suite + `bash scripts/docs-freshness.sh` green
- **Before `/gsd:verify-work`:** live E2E (`e2e.sh` + `e2e-rls.sh`) green against a **rebuilt, realm-re-imported** dev stack
- **Max feedback latency:** unit < ~10s; live E2E is a phase-gate, not per-task

---

## Per-Requirement Verification Map

All rows trace to **AI-1** (issue #203). Task IDs assigned by the planner; wave column indicative.

| Behavior | Requirement | Test Type | Automated Command | File Exists |
|----------|-------------|-----------|-------------------|-------------|
| `list_products` forwards Bearer + returns core JSON as tool content | AI-1 | unit (mock `coreGet`) | `npx vitest run src/tools/list-products.test.ts` | ❌ W0 |
| `list_shops` / `read_orders` same contract (incl. `read_orders` paging + `shopId`) | AI-1 | unit | `npx vitest run` | ❌ W0 |
| non-2xx `application/problem+json` → `isError` result, sanitized message, **no stack trace** | AI-1 | unit | `npx vitest run src/errors.test.ts` | ❌ W0 |
| network/timeout → `isError` "unreachable/timeout", **token never logged** | AI-1 | unit | `npx vitest run` | ❌ W0 |
| stateless transport builds a per-request server; missing Bearer → 401 | AI-1 | integration (supertest) | `npx vitest run src/index.test.ts` | ❌ W0 |
| **read happy-path** — `integration-catalog-ro` token → `list_products` → 200 rows (tenant A) | AI-1 | **live E2E** | `mcp-server/scripts/e2e.sh` | ❌ W0 |
| **cross-tenant RLS proof** — token A sees A's rows and NOT B's; token B sees NOT A's | AI-1 | **live E2E** (two real tokens at HTTP boundary) | `mcp-server/scripts/e2e-rls.sh` | ❌ W0 |
| docs-freshness stays green with the new MCP test family counted | AI-1 | CI gate | `bash scripts/docs-freshness.sh` | ✅ (extend) |

---

## Cross-tenant RLS proof design (the load-bearing AC)

1. Obtain **two genuinely tenant-scoped tokens** at the HTTP boundary via the **direct-access password grant** against `core-api` (`directAccessGrantsEnabled: true`): `tenant-a-user` (tenant …0001) and `tenant-b-user` (tenant …0002). Zero realm change. (`integration-catalog-ro` client-credentials token is used for the read happy-path + README as the locked reference credential.)
2. Ensure **disjoint seeded data**: extend dev-profile `DemoDataSeeder` to seed one shop+product for tenant B (tenant A already seeded). Gives an **unfakeable, bidirectional** assertion instead of a doubly-explained "empty for B."
3. Assert **through the MCP tool** (not a raw core curl — the proof must exercise the server):
   - `list_products` token A → contains `P_A`, does **not** contain `P_B`.
   - `list_products` token B → contains `P_B`, does **not** contain `P_A`.
   - order read token A for one of B's order ids → 404/empty (never B's row).
4. Runs against the **live dev stack** with real tokens — superuser Testcontainers cannot prove RLS (Pitfall 4). Gate the whole E2E behind "rebuild ALL containers + re-import realm first."

---

## Wave 0 Requirements

- [ ] `mcp-server/package.json` + `mcp-server/tsconfig.json` + `mcp-server/vitest.config.ts` (workspace scaffold)
- [ ] `mcp-server/src/**/*.test.ts` — unit tests for each tool + `errors.ts` + `core-client.ts` (mock fetch)
- [ ] `mcp-server/src/index.test.ts` — express/transport integration (supertest)
- [ ] `mcp-server/scripts/e2e.sh` + `e2e-rls.sh` — live token mint (client-credentials + password grant) + MCP call assertions
- [ ] `scripts/docs-freshness.sh` extension (new `mcp_test_blocks`/`mcp_test_files` family, path-anchored to `mcp-server/`) + `docs/metrics.json` via `--write`
- [ ] dev-profile `DemoDataSeeder` tenant-B seed (for the bidirectional RLS proof)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Realm re-import + full container rebuild before live E2E | AI-1 | Operational precondition; the `integration-catalog-ro` client does not exist in the currently-running IdP | Re-import per `docs/security-scopes.md §Re-import`, `docker compose build` all services, bring up stack, then run `e2e.sh` |
| MCP client (e.g. Claude) can actually connect and call a tool | AI-1 | End-user experience of the deliverable | Configure an MCP client against the running server per README; invoke `list_products`; observe rows |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags (use `vitest run`, not `vitest`)
- [ ] Feedback latency < 10s (unit)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending

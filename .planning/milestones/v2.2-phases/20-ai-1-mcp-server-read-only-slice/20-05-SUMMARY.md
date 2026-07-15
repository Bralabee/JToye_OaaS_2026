---
phase: 20-ai-1-mcp-server-read-only-slice
plan: 05
subsystem: ops
tags: [mcp, live-e2e, rls-proof, keycloak, realm-import, docker, client-credentials]

# Dependency graph
requires:
  - phase: 20-03
    provides: "jtoye-mcp-server compose service + Dockerfile + tenant-B probe seed (tenant-b-probe / TENANTB-PROBE-1)"
  - phase: 20-04
    provides: "mcp-server/scripts/e2e.sh (read happy-path) + e2e-rls.sh (cross-tenant RLS proof)"
  - phase: "#206 scoped machine credentials"
    provides: "integration-catalog-ro client + catalog:read scope in the realm template (live only after re-import)"
provides:
  - "LIVE-proven read happy-path: integration-catalog-ro token -> MCP list_products -> 200, 20 tenant-A rows (AC#4)"
  - "LIVE-proven cross-tenant isolation: disjoint NON-EMPTY bidirectional product sets through the MCP tool + no order leak (AC#5)"
  - "Real-MCP-client proof: Claude Code (headless, --mcp-config) connected, listed 3 tools, list_products returned 20 rows / 46 total"
  - "Rebuilt full stack on current source + jtoye-dev realm re-imported (integration-catalog-ro exists in the running IdP)"
affects: [phase-verification, gsd-ship, "#203", "#209-wave-2"]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Realm re-import on a Postgres-backed Keycloak: volume drop is a NO-OP (realms live in the keycloak Postgres DB) — kc.sh import --override true + container restart is the effective path"
    - "Short-lived (300s) client-credentials tokens: mint immediately before client connect; persistent clients re-mint per session"

key-files:
  created: []
  modified:
    - ".env (INTEGRATION_CATALOG_RO_SECRET generated + appended; backup .env.bak-wave4 — NOT committed, env-only)"

key-decisions:
  - "Task 1 + Task 3 human gates were resolved by the ORCHESTRATOR as the user's explicit delegate; the user gave the final 'Approved' sign-off on Task 3 after reviewing the delegated real-client evidence"
  - "Stack started WITHOUT the ollama/ollama-init containers: host-level Ollama systemd service holds 127.0.0.1:11434 and the compose publish '11434:11434' cannot bind; nothing in the E2E path depends on ollama (core-java depends only on postgres/keycloak/redis/rabbitmq) and the pre-Wave-4 stack also ran without an ollama container"
  - "INTEGRATION_CATALOG_RO_SECRET generated via openssl rand -hex 24 (dev-only credential, injected via .env config layer, never committed/printed)"

patterns-established:
  - "Live E2E gate: rebuild ALL -> re-import realm (override) -> restart KC -> mint-verify -> scripted proofs -> real-client check"

# Metrics
duration: ~50min (incl. full docker build + realm ops + live sweeps)
completed: 2026-07-13
---

# Phase 20 Plan 05: Live E2E — rebuild, re-import, prove (Summary)

**All three success criteria met live.** Stack rebuilt from current source, `jtoye-dev` re-imported so `integration-catalog-ro` exists in the running IdP, both scripted proofs green at the live HTTP boundary, and a real MCP client (Claude Code headless) connected and read tenant rows. Human sign-off: **Approved** (delegated evidence accepted).

## Task 1 — Rebuild ALL containers + re-import realm (checkpoint, resolved)

- `.env`: `INTEGRATION_CATALOG_RO_SECRET` was MISSING; generated (48 hex chars) and appended; backup at `.env.bak-wave4`. `KC_SEED_USER_PASSWORD` and `KEYCLOAK_CLIENT_SECRET` already present.
- `docker compose -f docker-compose.full-stack.yml build` — exit 0, all four source images rebuilt (mcp-server, core-java, edge-go, frontend).
- **Deviation (environment, documented):** `docker compose up -d` failed on `jtoye-ollama` — host Ollama systemd service (active) holds `127.0.0.1:11434`; compose hardcodes publish `"11434:11434"`. Started all services EXCEPT ollama/ollama-init (safe: only `ollama-init` depends on `ollama`; core-java does not). Matches the pre-Wave-4 running state (no ollama container).
- **Discovery (doc nuance):** dropping `jtoye_oaas_2026_keycloak_data` did NOT remove the realm — Keycloak runs `KC_DB: postgres` (realms live in the shared Postgres `keycloak` DB), so `--import-realm` logged "Realm 'jtoye-dev' already exists. Import skipped" and the mint failed `invalid_client`. Effective path per `docs/security-scopes.md` §4 option 2: `docker exec jtoye-keycloak /opt/keycloak/bin/kc.sh import --file /opt/keycloak/data/import/realm-export.json --override true` ("Removing it before import" → "Realm 'jtoye-dev' imported"), then `docker restart jtoye-keycloak` to clear the in-memory realm cache.
- **Verification (all three checks):** stack ps all healthy (keycloak, core-java, mcp-server, edge-go, frontend, postgres, redis, rabbitmq, minio, mailhog); token mint → `TOKEN_MINT_OK, expires_in=300, scope="email catalog:read profile"`; `GET :9100/health` → 200.

## Task 2 — Live E2E sweep (auto)

- `bash mcp-server/scripts/e2e.sh` → exit 0, ALL PASS: secret set → MCP healthy → `integration-catalog-ro` token minted → `POST /mcp` `list_products` → HTTP 200, `isError` absent, **20 product rows** (non-empty tenant-A set). **AC#4 satisfied live.**
- `bash mcp-server/scripts/e2e-rls.sh` → exit 0, ALL PASS: both tenant password-grant tokens minted (`tenant-a-user`/`tenant-b-user`) → both reach `list_products` (200 + aud gate) → tenant A CONTAINS `MAK-JOL`, NOT `TENANTB-PROBE-1`; tenant B CONTAINS `TENANTB-PROBE-1`, NO tenant-A product; tenant A `read_orders` has NO tenant-B marker. **Disjoint, non-empty, bidirectional — AC#5 satisfied live.**
- Evidence captured as status + row counts/markers only; no tokens or PII bodies logged (T-20-01 honoured).

## Task 3 — Real MCP client (checkpoint, human-approved)

- Protocol pre-check: full client handshake works — `initialize` → 200 (`protocolVersion 2025-06-18`, serverInfo `jtoye-mcp 0.1.0`), `notifications/initialized` → 202, `tools/list` → `list_products`, `list_shops`, `read_orders`.
- **Real client:** Claude Code headless (`claude -p --mcp-config`) with a fresh 300s token: connected, listed the three tools, invoked `list_products` → **20 products page 1 / 46 total / example "Sobo Punch"**.
- User's first interactive attempt failed ("can not get mcp") — consistent with the 300s token TTL expiring between mint and connect; working one-liner (mint + `claude mcp add` in one shell) provided and documented in `mcp-server/README.md` terms.
- **Human sign-off: "Approved"** — delegated real-client evidence accepted.

## Deviations from plan

| # | Type | What | Why |
|---|------|------|-----|
| 1 | Environment | Stack runs without ollama/ollama-init containers | Host Ollama holds :11434; hardcoded compose publish can't bind; not on the E2E path |
| 2 | Procedure | Volume drop replaced by `kc.sh import --override true` + KC restart | Keycloak is Postgres-backed; the volume does not hold realm state |

## Follow-ups (not blocking)

- Parameterize the ollama host-port publish (`"${OLLAMA_HOST_PORT:-11434}:11434"`) so full `up` works alongside a host Ollama (config-injection rule).
- Clarify `docs/security-scopes.md` §4: on this stack "drop the Keycloak schema/volume" means the **Postgres `keycloak` DB**, not the docker volume; the `kc.sh import --override true` + restart path is the reliable one.

## Self-Check: PASSED

- All 3 tasks executed; both human gates resolved (delegated + final user approval)
- `bash e2e.sh && bash e2e-rls.sh` → both exit 0 (LIVE_E2E_GREEN)
- Real MCP client connected and read rows
- No repo files modified (files_modified: [] honoured); schema stays V50; metrics stay 1231

---
phase: 20-ai-1-mcp-server-read-only-slice
plan: 03
subsystem: infra
tags: [mcp, docker, docker-compose, dev-seed, rls, keycloak, client-credentials, pass-through, readme]

# Dependency graph
requires:
  - phase: "20-01 MCP walking slice"
    provides: "mcp-server/ TypeScript ESM workspace (index.ts POST /mcp + GET /health on :9100, CORE_BASE_URL client, committed package-lock.json) — the artifact this container packages"
  - phase: "#206 scoped machine credentials"
    provides: "integration-catalog-ro client-credentials client + INTEGRATION_CATALOG_RO_SECRET + core-api audience + tenant_id claim (the reference Bearer the README documents minting)"
  - phase: "V13 seed_default_tenants"
    provides: "tenants(…0002) 'Tenant B' row — satisfies the shops.tenant_id FK for the tenant-B probe seed"
provides:
  - "mcp-server/Dockerfile — multi-stage node:20-alpine builder→runner, non-root uid 1001, tsc→dist, /health HEALTHCHECK on :9100"
  - "mcp-server/.dockerignore — node_modules/dist/.env/IDE exclusions"
  - "docker-compose.full-stack.yml mcp-server service — jtoye-mcp-server, depends_on core-java+keycloak (service_healthy), CORE_BASE_URL=http://core-java:9090, NO secret, jtoye-network, self /health healthcheck"
  - "DemoDataSeeder tenant-B probe — one shop (tenant-b-probe) + one product (TENANTB-PROBE-1) under TenantContext.set(TENANT_B) for a disjoint NON-EMPTY cross-tenant RLS proof"
  - "mcp-server/README.md — pass-through auth model + integration-catalog-ro client-credentials mint + rebuild-all+realm-re-import live preconditions + verify curl"
affects: [20-04-e2e-rls, 20-05-live-e2e, mcp-server, docker-compose, DemoDataSeeder]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MCP container = pass-through: NO client secret and NO DB creds in the compose block (T-20-07); CORE_BASE_URL is a fixed internal service name, never caller-controlled (T-20-04 SSRF guard)"
    - "Dev-seed second-tenant block: independent TenantContext.set/clear + transactionTemplate.execute per tenant so the RLS GUC pins to each tenant's writes (mirrors the tenant-A pattern)"
    - "Disjoint NON-EMPTY RLS proof fixture: seed tenant B one row so the cross-tenant assertion is unfakeable rather than a doubly-explained empty set"

key-files:
  created:
    - "mcp-server/Dockerfile"
    - "mcp-server/.dockerignore"
    - "mcp-server/README.md"
  modified:
    - "docker-compose.full-stack.yml"
    - "core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java"

key-decisions:
  - "Runner stage runs its OWN `npm ci --omit=dev` (prod deps only) + copies dist/ from builder — smaller runtime image than shipping the builder's full node_modules; mirrors the manifest-first cache ordering of frontend/Dockerfile"
  - "Compose mcp-server block carries ZERO secret env (pass-through LOCKED decision) — no INTEGRATION_CATALOG_RO_SECRET, no issuer/JWKS env; the container forwards an opaque token and never validates iss"
  - "Tenant-B seed uses a dedicated seedTenantB() with minimal TENANT_B-scoped upserts (NOT the existing upsertShop/upsertProduct helpers, which hardcode DEMO_TENANT as tenant_id and would violate the tenant-B RLS WITH CHECK)"
  - "No new Java @Test for the seed — RLS cannot be proven under superuser Testcontainers (RESEARCH Pitfall 4); the proof is the live cross-tenant E2E in 20-05. Java @Test baseline (872) unchanged; schema stays V50"

patterns-established:
  - "Second-tenant dev-seed block with its own RLS GUC scope for cross-tenant test fixtures"
  - "Secret-free pass-through container in compose (auth delegated entirely to core + RLS)"

requirements-completed: [AI-1]

# Metrics
duration: 15min
completed: 2026-07-13
---

# Phase 20 Plan 03: AI-1 MCP Server Packaging, Tenant-B Seed & README Summary

**The MCP server is now a buildable, secret-free `node:20-alpine` container wired into the full-stack compose (depending on healthy core-java + keycloak), tenant B carries a one-shop/one-product probe fixture so the final-wave cross-tenant RLS proof asserts DISJOINT NON-EMPTY sets, and a README documents minting the `integration-catalog-ro` reference token under the locked pass-through auth model — everything the live-E2E wave needs is now present and reachable.**

## Performance

- **Duration:** ~15 min
- **Completed:** 2026-07-13
- **Tasks:** 3 (all `type="auto"`, fully autonomous)
- **Files:** 3 created (Dockerfile, .dockerignore, README.md) + 2 modified (docker-compose.full-stack.yml, DemoDataSeeder.java)

## Accomplishments

- **Task 1 — Container + compose wiring:** `mcp-server/Dockerfile` mirrors `frontend/Dockerfile`'s canonical two-stage `node:20-alpine` build (manifest-first `COPY` → `npm ci` → `COPY . .` → `npm run build`), with a non-root uid-1001 runner that does its own `npm ci --omit=dev`, copies `dist/`, `EXPOSE 9100`, and a `node -e http.get` HEALTHCHECK on `:9100/health`. `.dockerignore` copies the frontend exclusions, swapping Next's `.next/`/`out/` for `dist/`. The new `mcp-server:` compose service (`jtoye-mcp-server`) depends on `core-java` **and** `keycloak` (`service_healthy`), sets `CORE_BASE_URL: http://core-java:9090`, exposes `9100:9100`, joins `jtoye-network`, and carries **no secret**. `docker compose config` parses and shows the service.
- **Task 2 — Tenant-B probe seed:** added `TENANT_B` (…0002) + a `seedTenantB()` that upserts exactly one shop (`tenant-b-probe`) and one product (`TENANTB-PROBE-1`) inside its **own** `TenantContext.set(TENANT_B)` + `transactionTemplate.execute` block, so `TenantSetLocalAspect` pins the RLS GUC to tenant B for those writes. Idempotent (upsert by slug/SKU); no Flyway (schema stays V50); `./gradlew :core-java:compileJava` succeeds.
- **Task 3 — README:** `mcp-server/README.md` documents what the server is (stateless read-only MCP; RLS the sole boundary; no secret/no DB creds), the three tools and their core endpoints, the **pass-through** auth model as the default-and-built path, the `integration-catalog-ro` + `INTEGRATION_CATALOG_RO_SECRET` client-credentials mint (host `:8085`, secret from `.env`), the rebuild-all + realm **Re-import** live precondition (`docs/security-scopes.md` §4) and verify curl, and an explicit note that the server-minted in-container fallback is **NOT** built in this slice.

## Task Commits

1. **Task 1:** `b3d6204` (feat) — Dockerfile + .dockerignore + mcp-server compose service
2. **Task 2:** `8aed811` (feat) — DemoDataSeeder tenant-B probe (shop + product)
3. **Task 3:** `7a0dbe2` (docs) — README (client-credentials setup + pass-through + preconditions)

## Files Created/Modified

- `mcp-server/Dockerfile` — multi-stage node:20-alpine, non-root, `dist/` copy, `:9100/health` HEALTHCHECK, `CMD ["node","dist/index.js"]`.
- `mcp-server/.dockerignore` — node_modules/, `dist/`, `.env`, IDE/OS/coverage/log exclusions.
- `mcp-server/README.md` — server description, tool→endpoint table, pass-through auth, reference-token mint, live E2E preconditions, optional-not-built note.
- `docker-compose.full-stack.yml` — new `mcp-server:` service block (build ./mcp-server, depends_on core+keycloak healthy, CORE_BASE_URL, ports 9100, no secret, self healthcheck).
- `core-java/.../dev/DemoDataSeeder.java` — `TENANT_B`/slug/SKU constants, a second RLS-scoped seed block in `run()`, and `seedTenantB()`.

## Decisions Made

- **Prod-deps-only runner:** the runner stage runs its own `npm ci --omit=dev` and copies only `dist/` from the builder, rather than copying the builder's dev-laden `node_modules` — a leaner runtime image while keeping the manifest-first cache ordering.
- **No compose secret:** the mcp-server block carries no `INTEGRATION_CATALOG_RO_SECRET` and no issuer/JWKS env — the pass-through model holds no secret in the container (T-20-07) and never validates `iss`.
- **Dedicated `seedTenantB()` (not the shared helpers):** the existing `upsertShop`/`upsertProduct` hardcode `DEMO_TENANT` as `tenant_id`; reusing them under the tenant-B GUC would violate the RLS `WITH CHECK`. A minimal TENANT_B-scoped upsert avoids that while staying idempotent.
- **No Java @Test for the seed:** superuser Testcontainers cannot prove RLS (RESEARCH Pitfall 4); the disjoint-set proof is the live cross-tenant E2E in 20-05. Java `@Test` baseline (872) unchanged; schema stays V50.

## Deviations from Plan

None — plan executed exactly as written. All three tasks' actions, verifications, and acceptance criteria were met on the first pass; no auto-fix loops.

_Note: the FK `shops.tenant_id → tenants(id)` (V1) initially looked like it might block the tenant-B insert, but V13 already seeds the `tenants(…0002)` 'Tenant B' row, so the probe seed satisfies the constraint with no schema change. This was a verification-before-write check, not a deviation._

## Issues Encountered

- `./gradlew` lives at the repo root, not under `core-java/` (the plan's verify snippet used `cd core-java && ./gradlew`). Compiled via the subproject task `./gradlew :core-java:compileJava` from the worktree root instead — EXIT=0. The only warnings are pre-existing MapStruct "Unmapped target properties" notices in `ShopMapper.java` (out of scope, not touched).

## Known Stubs

None. The Dockerfile, compose service, seed and README are all fully realised. `list_shops`/`read_orders` referenced in the README are the intended tool surface being added in parallel by 20-02 (not stubbed by this plan — this plan owns no `src/tools/` files).

## Threat Flags

None. All security-relevant surface introduced here (the compose `CORE_BASE_URL`, the secret-free block, the dev-only tenant-B rows, the forwarded Bearer) is already covered by the plan's threat register (T-20-04 / T-20-06 / T-20-07 / T-20-08 / T-20-88). No new endpoint, auth path or trust-boundary schema change beyond the plan.

## Next Phase Readiness

- **20-04/05 (live E2E + cross-tenant RLS proof):** the container is buildable and reachable at `:9100`; tenant B has a disjoint non-empty fixture (`TENANTB-PROBE-1`); and the README's mint + rebuild-all + realm re-import steps are the documented blocking gate (T-20-06). The realm re-import against the live IdP is still an operator action (not automatable here).
- **docs-freshness:** this plan added no vitest blocks; the `mcp_test_*` family + `docs/metrics.json` bump remains owned by a later CI-gate task (unchanged from the 20-01 note).

## Self-Check: PASSED

- All 3 created files (`mcp-server/Dockerfile`, `mcp-server/.dockerignore`, `mcp-server/README.md`) and 2 modified files verified present on disk.
- All 3 task commits (`b3d6204`, `8aed811`, `7a0dbe2`) verified in git log.
- `docker compose -f docker-compose.full-stack.yml config` parses and shows exactly one `jtoye-mcp-server` container; `./gradlew :core-java:compileJava` clean; README greps (`integration-catalog-ro`, `INTEGRATION_CATALOG_RO_SECRET`, `Re-import`) all match; no literal secret value in the README.

---
*Phase: 20-ai-1-mcp-server-read-only-slice*
*Completed: 2026-07-13*

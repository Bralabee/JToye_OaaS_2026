# Housekeeping Report — Post-Audit Sweep

**Date:** 2026-04-14
**Branch:** `chore/housekeeping-post-audit`
**Context:** Ran immediately after Phases 1-5 of the codebase audit fix campaign. All fix branches (`fix/edge-go-security-hardening`, `fix/java-core-data-integrity`, `fix/frontend-security-and-tests`, `fix/infra-hardening`, `fix/low-touch-cleanup`) are still open, unpushed, awaiting review+merge.

## Summary

| #  | Area              | Status | Details |
|----|-------------------|--------|---------|
| 1  | Git Hygiene       | ✅ CLEAN | 5 open fix branches + new chore branch. Remote cleaned: 7 stale origin refs pruned. Only `origin/main` remains. |
| 2  | Build Health      | ✅ CLEAN | core-java compileJava OK on main (2 pre-existing mapper warnings, 1 deprecation). frontend `next build` clean. edge-go `go build ./...` clean. |
| 3  | Tests             | ✅ CLEAN | edge-go 28 tests (uncached), frontend 43/43 Jest (on main), core-java BUILD SUCCESSFUL (per Phase 2 run: 335 tests on its branch). |
| 4  | YAML Validation   | ✅ CLEAN | `docker compose -f docker-compose.full-stack.yml config --quiet` exit 0. |
| 5  | Spring Context    | ✅ CLEAN | `TenantCacheEvictor` (Phase 2) uses `ObjectProvider<CacheManager>` to tolerate `@Profile("!test")` exclusion — test profile still boots. |
| 6  | Docs Freshness    | ✅ CLEAN | README badge v2.0.0, CHANGELOG v2.0.0. `CLAUDE.md` V28→V30 corrected (Phase 4). Next sweep after Phase 2 merge should bump to V32. |
| 7  | Dependencies      | ✅ FIXED | `go mod tidy`: no drift. `npm audit fix` applied — see Actions. |
| 8  | Security          | ✅ FIXED | Critical + high + moderate vulns all closed. See Actions. |
| 9  | Env Parity        | ✅ CLEAN | All `${VAR}` in compose have `.env.example` entries. 4 orphans in env.example (`RATE_LIMIT_RPS/BURST`, `NOTIFICATION_EMAIL_*`) are read directly by edge-go / core-java, not via compose → expected. |
| 10 | Docker            | ⚠️ N/A | J'Toye stack not running. Only unrelated `dealflow_*` containers present. No stale-image check possible. |
| 11 | Migrations        | ✅ CLEAN | 30 Flyway files, sequentially numbered V1–V30, no gaps or duplicates. Phase 2 adds V31 + V32 on its branch. |
| 12 | Artifact Cleanup  | ✅ CLEAN | No `*.orig`, `*.bak`, `.DS_Store`, `*.swp`. |
| 13 | Code Quality      | ✅ CLEAN | Only 1 TODO in entire source tree: `frontend/app/track/page.tsx:1`. |
| 14 | CI/CD             | ✅ OK   | 1 workflow: `.github/workflows/ci-cd.yaml`. Not invalidated. |
| 15 | E2E Smoke Test    | ⏭ SKIP | J'Toye stack not running. Re-run after Phase 3 merges + stack up. |

## Actions Taken

1. **Pruned 7 stale origin branches** (`chore/gap-cleanup`, `chore/housekeeping-post-batch5`, `chore/housekeeping-post-tier2`, `feat/batch4-infra`, `feat/m3-infra-hardening`, `feat/tier2-reliability`, `feat/tier3-enhancements`) — all had been deleted on remote but still tracked locally.
2. **Created branch `chore/housekeeping-post-audit` off main.**
3. **`npm install axios@^1.15.0 --save`**: 1.14.0 → 1.15.0. Closes critical SSRF + cloud-metadata-exfiltration CVE.
4. **`npm audit fix`**: resolved `follow-redirects` (moderate, auth-header leak) and `next` (high, Server Components DoS).
5. **Post-fix verification**: `npm audit` → 0 vulnerabilities. `npm test` → 43/43 pass. `npm run build` → clean.
6. **One atomic commit**: `chore(deps): npm audit fix — close axios SSRF, follow-redirects, next DoS` (b504aa4).

## Recommended Actions (ordered by severity)

1. **HIGH — merge Phase 3 first then cherry-pick the axios bump** (or vice versa). The `fix/frontend-security-and-tests` branch includes new api-client code; the axios bump on `chore/housekeeping-post-audit` is on an older package.json. Resolve the overlap during review/merge so the critical fix doesn't get lost.
2. **MED — open PRs for all 6 branches in this order** to minimize merge conflicts:
   1. `fix/edge-go-security-hardening` (isolated to edge-go/ and k8s probes)
   2. `fix/low-touch-cleanup` (isolated to scripts/ + .env.example snippet)
   3. `fix/infra-hardening` (k8s manifests + compose + CLAUDE.md — CLAUDE.md will need bumping to V32 after Phase 2 merges)
   4. `fix/java-core-data-integrity` (largest, introduces V31 + V32 migrations)
   5. `chore/housekeeping-post-audit` (axios + follow-redirects + next bumps)
   6. `fix/frontend-security-and-tests` (depends on the dep bumps; rebase on top to pick them up)
3. **MED — after Phase 2 merges**, bump `CLAUDE.md` Flyway version V30 → V32 (Phase 4 only corrected to V30 because main had V30 at branch time).
4. **LOW — consider running `npm fund` info** for 184 packages seeking funding (no action required, just awareness).
5. **LOW — address the single TODO in `frontend/app/track/page.tsx:1`** or delete it.
6. **LOW — bring the J'Toye stack up and re-run housekeeping** with the E2E smoke test active.

## Skipped Checks

- **#10 Docker & Containers** — J'Toye stack not running. Only unrelated `dealflow_*` containers present on the daemon. Cannot check image staleness against non-existent containers.
- **#15 E2E Smoke Test** — requires stack up; re-run after merge + `./scripts/start-dev.sh`.

## Deltas vs Pre-Audit State

| Metric | Before | After |
|---|---|---|
| Audited CVEs (frontend) | 3 (1 critical, 1 high, 1 moderate) | **0** |
| TODO/FIXME in source | ? | **1** |
| Stale origin branches | 7 | **0** |
| Flyway sync in CLAUDE.md | V28 | V30 (will be V32 after Phase 2 merge) |
| `:latest` tags in production manifests | 7 | **0** |
| Fix branches open | 0 | 5 (+ 1 housekeeping chore branch) |

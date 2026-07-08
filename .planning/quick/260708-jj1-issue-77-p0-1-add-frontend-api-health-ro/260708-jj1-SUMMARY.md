---
phase: 260708-jj1-issue-77-p0-1-add-frontend-api-health-ro
plan: 01
subsystem: frontend
tags: [k8s, healthcheck, liveness, readiness, docker, issue-77]
requires: []
provides:
  - "GET /api/health unauthenticated liveness/readiness route"
  - "compose frontend healthcheck aligned to /api/health"
affects:
  - k8s frontend liveness/readiness probes (now resolve to an existing route)
  - docs-freshness CI gate (metrics manifest refreshed)
tech-stack:
  added: []
  patterns: ["Next.js route handler idiom (NextResponse.json + force-dynamic)"]
key-files:
  created:
    - frontend/app/api/health/route.ts
    - frontend/app/api/health/__tests__/route.test.ts
  modified:
    - docker-compose.full-stack.yml
    - docs/metrics.json
    - CLAUDE.md
decisions:
  - "Committed route + test as ONE atomic commit (plan specified single commit; docs-freshness evaluates the final tree, so the stale-metrics gap between commit 1 and 2 is intentional and consistent)"
  - "Task 3 (checkpoint:human-verify) executed autonomously per orchestrator override; evidence recorded below for human confirmation"
metrics:
  duration: ~9 min
  completed: 2026-07-08
  branch: fix/77-frontend-health-probe
  commits: 2
---

# Phase 260708-jj1 Plan 01: Frontend /api/health Route Summary

Added the missing `GET /api/health` route so the k8s frontend liveness/readiness probes and the Dockerfile HEALTHCHECK (both already targeting `/api/health:3000`) resolve to a real 200 endpoint, ending the UI-pod crash-loop described in issue #77; aligned the docker-compose healthcheck that was masking the bug by probing `/`, and kept the docs-freshness test-count manifest honest.

## What Was Built

- **`frontend/app/api/health/route.ts`** — unauthenticated `GET()` returning `NextResponse.json({ status: "ok" }, { status: 200 })`, marked `export const dynamic = "force-dynamic"` so a probe always hits live (never prerendered/cached) code. No cookie reads, no request body — intentionally public, and `frontend/middleware.ts` matcher `["/dashboard/:path*"]` does not intercept it.
- **`frontend/app/api/health/__tests__/route.test.ts`** — node-env Jest contract test with exactly two `it()` blocks (status 200; body deep-equals `{status:"ok"}`).
- **`docker-compose.full-stack.yml`** — frontend healthcheck `test` line changed from `http://127.0.0.1:3000` to `http://127.0.0.1:3000/api/health` (interval/timeout/retries/start_period unchanged). This removes the mask: the old probe hit `/` which always 200s.
- **`docs/metrics.json`** + **`CLAUDE.md`** — regenerated via `scripts/docs-freshness.sh --write`; only Jest fields moved (jest_blocks 100→102, jest_files 17→18, total 692→694), CLAUDE.md line 15 paragraph mirrored to match.

## Tasks Executed

| Task | Name | Commit | Files |
| ---- | ---- | ------ | ----- |
| 1 | Add /api/health route + Jest contract test | `e9768ff` | frontend/app/api/health/route.ts, frontend/app/api/health/__tests__/route.test.ts |
| 2 | Align compose healthcheck + refresh metrics | `9712eb2` | docker-compose.full-stack.yml, docs/metrics.json, CLAUDE.md |
| 3 | Live verification (rebuild frontend, curl + inspect) | (no commit) | none — verification only |

## Verification Evidence (actual recorded output)

### Task 1 — scoped Jest (`cd frontend && npx jest app/api/health --verbose`)
```
PASS app/api/health/__tests__/route.test.ts
  /api/health
    ✓ returns 200 (3 ms)
    ✓ returns a {status:'ok'} body (1 ms)
Test Suites: 1 passed, 1 total
Tests:       2 passed, 2 total
```

### Task 1 — full frontend suite (`cd frontend && npm test`)
```
Test Suites: 18 passed, 18 total
Tests:       101 passed, 101 total
Snapshots:   1 passed, 1 total
```
(No failures. Pre-existing React `act()` warnings in `app/dashboard/kitchen/page.tsx` test are unchanged and out of scope. Note: the runner's executed-test count 101 differs from the static `jest_blocks=102` because docs-freshness statically counts `\b(it|test)\(` occurrences across source files, while the runner counts executed cases; the plan drives the metric from the script's own count.)

### Task 2 — docs-freshness gate (`bash scripts/docs-freshness.sh`)
```
docs-freshness OK: metrics match source (total logical invocations: 694).
```
Exit code: `0`. Metrics diff confirmed ONLY Jest fields moved:
```
-  "jest_blocks": 100,
-  "jest_files": 17,
+  "jest_blocks": 102,
+  "jest_files": 18,
-  "total_logical_invocations": 692
+  "total_logical_invocations": 694
```
Combined verify chain: `docs-freshness OK + api/health in compose + jest_blocks=102` → PASS.

### Task 3 — live rebuild + verification against running :3100 stack

**Pre-rebuild baseline (proof of the bug on the old image):**
```
GET http://localhost:3100/api/health  → HTTP 404 (route absent)
docker inspect ... Health.Status       → healthy  (FALSE positive: old healthcheck probes /)
```

**Rebuild (frontend only):**
```
docker compose -f docker-compose.full-stack.yml -f docker-compose.frontend-3100.yml build frontend
  → Image jtoye_oaas_2026-frontend Built
docker compose ... up -d frontend
  → Container jtoye-frontend Started  (dependencies only health-waited, NOT restarted)
```

**Post-rebuild live checks:**
| Check | Command | Result |
| ----- | ------- | ------ |
| (a) HTTP status, no auth cookie | `curl -s -o /dev/null -w '%{http_code}' http://localhost:3100/api/health` | `200` |
| (b) Response body | `curl -s http://localhost:3100/api/health` | `{"status":"ok"}` |
| (c) Container health | `docker inspect --format '{{.State.Health.Status}}' jtoye-frontend` | `healthy` |
| (d) Path equivalence | k8s probes (frontend-deployment.yaml L85, L93) + Dockerfile HEALTHCHECK (L64-65) | all target `/api/health` — unchanged |

- Container status line: `jtoye-frontend  Up 30 seconds (healthy)`; `StartedAt=2026-07-08T13:11:09Z`.
- Health-check log last probe: `exit=0` (the NEW `/api/health` probe genuinely succeeded — contrast with the pre-rebuild false-healthy on `/`).
- Health settled to `healthy` within the first poll after recreate (well inside the 40s start_period).
- **Other stack services untouched:** postgres/keycloak/redis/rabbitmq/minio/mailhog/edge-go still `Up 2 days`; core-java/grafana/prometheus/alertmanager still `Up 15 hours` — none restarted.

## Deviations from Plan

None — plan executed exactly as written. Task 3, marked `checkpoint:human-verify` in the plan, was run autonomously per the orchestrator's explicit override; all four evidence items (a–d) are recorded above for the human-verify step the orchestrator performs afterward.

## Threat Surface

No new surface beyond the plan's `<threat_model>`. `GET /api/health` emits only the fixed literal `{"status":"ok"}` (T-77-01 mitigated: no version/build/tenant/internal data). No package installs (T-77-SC n/a). Middleware auth path unchanged (T-77-03 accepted: `/api/health` excluded by matcher).

## Constraints Honored

- Only the frontend service was rebuilt/recreated; no other stack service restarted.
- Two atomic commits with conventional prefixes; no Co-Authored-By or any trailers.
- k8s manifests and `frontend/Dockerfile` left unchanged (path-equivalent to the new route).
- No push, no PR opened; docs artifacts (this SUMMARY / STATE / PLAN) and ROADMAP.md not committed.
- Local stack remained usable throughout (rebuild succeeded; container healthy).

## Self-Check: PASSED

- FOUND: frontend/app/api/health/route.ts
- FOUND: frontend/app/api/health/__tests__/route.test.ts
- FOUND commit: e9768ff
- FOUND commit: 9712eb2
- No trailers in either commit body.
- Live: 200 + `{"status":"ok"}` + container `healthy`.

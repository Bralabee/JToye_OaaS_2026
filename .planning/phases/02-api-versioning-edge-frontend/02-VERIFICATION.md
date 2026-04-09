---
phase: 02-api-versioning-edge-frontend
verified: 2026-04-07T00:00:00Z
status: passed
score: 6/6 must-haves verified
re_verification: null
gaps: []
human_verification:
  - test: "Browser-level end-to-end flow through Go edge to Spring Boot"
    expected: "Dashboard pages load real data via /api/v1/ paths without 404 errors"
    why_human: "Cannot start services in static verification; requires running docker-compose with both edge-go and core-java to confirm the full request path succeeds"
  - test: "SSE real-time order stream in dashboard"
    expected: "EventSource at /api/v1/orders/stream receives events when new orders arrive"
    why_human: "Real-time behaviour requires a live server and an actual order creation event"
---

# Phase 2: API Versioning — Edge & Frontend Verification Report

**Phase Goal:** The full request path from browser through Go edge to Spring Boot uses /api/v1/ consistently
**Verified:** 2026-04-07
**Status:** PASSED
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Go edge gateway proxies /sync/batch and /webhooks/whatsapp to /api/v1/ backend paths | VERIFIED | `edge-go/cmd/edge/main.go` lines 110, 143: `protected.POST("/api/v1/sync/batch", ...)` and `protected.POST("/api/v1/webhooks/whatsapp", ...)` |
| 2 | Go edge core client calls SearchProducts and CreateOrder at /api/v1/ paths | VERIFIED | `edge-go/internal/core/client.go` lines 80, 125: `/api/v1/sync/batch`, `/api/v1/webhooks/`; `edge-go/internal/core/orders.go` lines 45, 84: `/api/v1/products/search`, `/api/v1/orders` |
| 3 | Go edge /health remains at /health (no versioning on health check) | VERIFIED | `edge-go/cmd/edge/main.go` line 85: `r.GET("/health", ...)`. `edge-go/internal/core/client.go` line 162: `c.baseURL+"/health"`. No `/api/v1/health` anywhere in edge codebase. |
| 4 | All Next.js dashboard apiClient calls use /api/v1/ prefixed paths | VERIFIED | orders(8), shops(9), products(9), customers(4), dashboard(7), finance(2), import(3) — all match or exceed plan thresholds. No unversioned apiClient calls remain in dashboard pages. |
| 5 | SSE EventSource connection uses /api/v1/orders/stream | VERIFIED | `frontend/app/dashboard/orders/page.tsx` line 248: `` `${apiUrl}/api/v1/orders/stream` `` |
| 6 | Public storefront paths (/public/**) remain unchanged (no /api/v1/ prefix) | VERIFIED | `frontend/app/shop/` and `frontend/app/track/` use `publicApiClient` with `/public/` paths only. Grep for `/api/v1/` in those directories returns no matches. |

**Score:** 6/6 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `edge-go/cmd/edge/main.go` | Edge routes with /api/v1/ prefix | VERIFIED | Contains `/api/v1/sync/batch` (line 110) and `/api/v1/webhooks/whatsapp` (line 143) |
| `edge-go/internal/core/client.go` | Core client with versioned paths | VERIFIED | Contains `/api/v1/sync/batch` (line 80) and `/api/v1/webhooks/` (line 125); `/health` exempt on line 162 |
| `edge-go/internal/core/orders.go` | Order/product client with versioned paths | VERIFIED | Contains `/api/v1/products/search` (line 45) and `/api/v1/orders` (line 84) |
| `frontend/app/dashboard/orders/page.tsx` | Orders page with versioned API calls | VERIFIED | 8 occurrences of `/api/v1/` — meets plan threshold of 8+ |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `edge-go/cmd/edge/main.go` | core-java /api/v1/sync/batch | `coreClient.SyncBatch()` | WIRED | Route at `/api/v1/sync/batch` calls through to client; client sends to `c.baseURL+"/api/v1/sync/batch"` |
| `edge-go/internal/core/orders.go` | core-java /api/v1/orders | `coreClient.CreateOrder()` | WIRED | `c.baseURL+"/api/v1/orders"` confirmed on line 84 |
| `frontend/app/dashboard/*/page.tsx` | core-java /api/v1/* | `apiClient.get/post` | WIRED | All 7 dashboard pages use `apiClient.get("/api/v1/...")` or `apiClient.post("/api/v1/...")` patterns confirmed by grep |

### Data-Flow Trace (Level 4)

Level 4 trace applies to dashboard components rendering dynamic data. The data-flow chain is:

1. Next.js `apiClient` sends request to `NEXT_PUBLIC_API_URL` (points to Go edge)
2. Go edge receives the `/api/v1/*` route and forwards to `CORE_URL+"/api/v1/*"`
3. Spring Boot core-java responds with DB data (Phase 1 verified this layer)

The edge layer is a proxy (no data transformation). Level 4 verification of the edge client confirms the URL sent to core-java is `/api/v1/products/search` and `/api/v1/orders` — matching Phase 1's Spring Boot endpoint registration. Data flow is structurally connected. Full end-to-end requires running services (see Human Verification).

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|--------------------|--------|
| `orders/page.tsx` | orders state | `apiClient.get("/api/v1/orders?...")` | Passes to live backend | FLOWING |
| `products/page.tsx` | products state | `apiClient.get("/api/v1/products?...")` | Passes to live backend | FLOWING |
| `dashboard/page.tsx` | summary counts | `apiClient.get("/api/v1/shops?size=1")` etc. (7 calls) | Passes to live backend | FLOWING |
| `finance/page.tsx` | transactions | `apiClient.get("/api/v1/financial-transactions/summary")` | Passes to live backend | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Go edge tests pass | `cd edge-go && go test ./... -count=1` | 4 packages, all ok | PASS |
| Frontend unit tests pass | `cd frontend && npm test -- --passWithNoTests` | 43 tests pass across 5 suites | PASS |
| No old unversioned Go paths remain | `grep -rn '"/sync/batch"\|"/products/search"\|c.baseURL+"/orders"' edge-go/internal/core/` | No matches | PASS |
| ImageUploader callers pass /api/v1/ uploadUrl | `grep -rn 'uploadUrl.*api/v1' frontend/app/dashboard/` | products/page.tsx:618 and shops/page.tsx:458,479 all pass `/api/v1/` paths | PASS |

Note: One test suite (`e2e/storefront-flows.spec.ts`) fails with a Playwright-in-Jest runner conflict. This file was last modified in commit `1a5c0fa` (a prior phase) and is not touched by this phase. The 43 unit tests all pass.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| APIV-02 | 02-01-PLAN.md | Go edge gateway routes updated for /api/v1/ prefix | SATISFIED | `edge-go/cmd/edge/main.go` and `edge-go/internal/core/client.go` and `edge-go/internal/core/orders.go` all use `/api/v1/` paths for backend calls |
| APIV-03 | 02-01-PLAN.md | Next.js frontend API client updated to use /api/v1/ paths | SATISFIED | All 7 dashboard pages use `/api/v1/` prefixed `apiClient` calls; SSE stream uses `/api/v1/orders/stream` |

No orphaned requirements found for Phase 2 in REQUIREMENTS.md.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| None found | — | — | — | — |

No TODO/FIXME/placeholder comments found in any of the 12 modified files. No hardcoded empty returns. No stub handlers.

### Human Verification Required

#### 1. End-to-End Browser Flow

**Test:** Start `docker-compose up` (core-java + edge-go + frontend). Log in as a vendor, navigate to the dashboard orders page, and confirm orders load without 404 or network errors.
**Expected:** Dashboard loads real order data; browser network tab shows requests to `/api/v1/orders`, `/api/v1/shops`, `/api/v1/products` returning 200 responses.
**Why human:** Cannot start the full service stack in static verification. The wiring is confirmed in code, but the actual HTTP routing through Gin's registered routes to Spring Boot's WebMvcConfigurer prefix requires live services.

#### 2. SSE Real-Time Order Stream

**Test:** With services running, open the Orders dashboard page and create a new order via another session or API call.
**Expected:** The new order appears on the dashboard within seconds via the EventSource at `/api/v1/orders/stream`.
**Why human:** Real-time SSE behaviour cannot be verified without a running server emitting events.

### Gaps Summary

No gaps. All 6 observable truths are verified in the codebase. All 4 required artifacts exist, are substantive, are wired, and have data flowing to live backend paths. Both requirements APIV-02 and APIV-03 are satisfied. Both commit hashes (d3977a4, be5af50) confirmed in git log.

---

_Verified: 2026-04-07_
_Verifier: Claude (gsd-verifier)_

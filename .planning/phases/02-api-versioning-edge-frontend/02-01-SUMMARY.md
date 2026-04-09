---
phase: 02-api-versioning-edge-frontend
plan: 01
subsystem: edge-go, frontend
tags: [api-versioning, edge-gateway, frontend, path-migration]
dependency_graph:
  requires: [01-01]
  provides: [end-to-end-api-v1-paths]
  affects: [edge-go, frontend-dashboard]
tech_stack:
  added: []
  patterns: [url-prefix-versioning]
key_files:
  created: []
  modified:
    - edge-go/cmd/edge/main.go
    - edge-go/internal/core/client.go
    - edge-go/internal/core/orders.go
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/shops/page.tsx
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/products/import/page.tsx
    - frontend/app/dashboard/customers/page.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/__tests__/page.test.tsx
    - frontend/app/dashboard/products/__tests__/page.test.tsx
decisions:
  - "Health endpoint /health remains exempt from /api/v1/ prefix in both edge main.go and client.go"
  - "image-uploader.tsx unchanged -- receives uploadUrl as prop from callers which now pass /api/v1/ paths"
  - "Public storefront paths (/public/**) intentionally not modified -- exempt from versioning"
metrics:
  duration: 388s
  completed: "2026-04-08T07:57:38Z"
  tasks_completed: 2
  tasks_total: 2
  files_modified: 12
---

# Phase 02 Plan 01: API Versioning -- Edge & Frontend Summary

Go edge gateway routes, core client paths, and all Next.js dashboard API calls updated to /api/v1/ prefix, completing the end-to-end API versioning migration started in Phase 1.

## Tasks Completed

| # | Task | Commit | Key Changes |
|---|------|--------|-------------|
| 1 | Update Go edge gateway routes and core client paths | d3977a4 | Edge routes /sync/batch and /webhooks/whatsapp prefixed; core client SyncBatch, ForwardWebhook, SearchProducts, CreateOrder use /api/v1/; health remains at /health |
| 2 | Update Next.js dashboard API calls and SSE URL | be5af50 | All 7 dashboard pages updated (orders, shops, products, import, customers, dashboard, finance); SSE stream URL updated; image upload URLs updated; test mocks updated |

## Verification Results

- Go edge: All 26 tests pass (4 packages)
- Frontend: All 43 unit tests pass (5 suites)
- Edge versioned paths confirmed via grep (6 occurrences of /api/v1/)
- Health endpoint confirmed exempt (/health without prefix)
- Dashboard versioned paths: orders(8), shops(9), products(9), customers(4), dashboard(7), finance(2), import(3)
- Public storefront paths unchanged

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated test mocks for /api/v1/ paths**
- **Found during:** Task 2 verification
- **Issue:** Dashboard and products test files contained hardcoded API path expectations (e.g., `/shops?size=1`, `/financial-transactions/summary`) that no longer matched the updated component code
- **Fix:** Updated all mock URL matches and assertion expectations in `page.test.tsx` files to use `/api/v1/` prefix
- **Files modified:** `frontend/app/dashboard/__tests__/page.test.tsx`, `frontend/app/dashboard/products/__tests__/page.test.tsx`
- **Commit:** be5af50

## Known Stubs

None -- all paths are fully wired to the /api/v1/ backend endpoints.

## Self-Check: PASSED

All 12 modified files verified on disk. Both commit hashes (d3977a4, be5af50) confirmed in git log.

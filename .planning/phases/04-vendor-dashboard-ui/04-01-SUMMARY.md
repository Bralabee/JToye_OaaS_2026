---
phase: 04-vendor-dashboard-ui
plan: 01
subsystem: frontend-dashboard
tags: [marketing, promotions, announcements, crud, dashboard]
dependency_graph:
  requires: [03-01, 03-02]
  provides: [vendor-marketing-ui]
  affects: [frontend/types/api.ts, frontend/components/dashboard/sidebar.tsx]
tech_stack:
  added: []
  patterns: [tabbed-page, client-side-status-derivation, datetime-local-inputs]
key_files:
  created:
    - frontend/app/dashboard/marketing/page.tsx
  modified:
    - frontend/types/api.ts
    - frontend/components/dashboard/sidebar.tsx
decisions:
  - Implemented both Promotions and Announcements tabs in a single page component to share state (shops dropdown, status helpers)
  - Used client-side status derivation rather than server-side to avoid extra API calls
  - Used native datetime-local inputs per D-03 decision (no extra dependencies)
metrics:
  duration_seconds: 327
  completed: 2026-04-08T10:51:53Z
  tasks_completed: 3
  files_changed: 3
---

# Phase 04 Plan 01: Marketing Dashboard Page Summary

Vendor marketing dashboard with full CRUD for promotions (discount type toggle, scheduling) and announcements (optional scheduling), tabbed UI with client-side status filtering.

## What Was Built

### Task 1: TypeScript Types and Sidebar Link (e864454)
- Added `Promotion`, `CreatePromotionRequest`, `DiscountType` types to `frontend/types/api.ts`
- Added `Announcement`, `CreateAnnouncementRequest` types to `frontend/types/api.ts`
- Added Marketing nav link with Megaphone icon to sidebar navigation array

### Task 2: Marketing Page with Promotions Tab (4e6b09f)
- Created `/dashboard/marketing` page at `frontend/app/dashboard/marketing/page.tsx` (1225 lines)
- Tab bar with Promotions (default) and Announcements tabs
- Promotions tab: table with Label, Discount, Shop, Status, Valid From, Valid Until, Actions columns
- Status badges: Active (emerald), Upcoming (amber), Expired (slate), Disabled (red)
- Status filter bar: All / Active / Upcoming / Expired with client-side filtering
- Create/Edit dialog with discount type toggle (Percentage / Fixed Amount), shop dropdown, datetime-local inputs
- Delete confirmation dialog
- Empty state with Megaphone icon and create button
- Pagination, toast notifications on all CRUD operations

### Task 3: Announcements Tab with CRUD (included in 4e6b09f)
- Announcements tab with independent state and pagination
- Table with Title, Body (truncated 80 chars), Shop, Status, Valid From, Valid Until, Actions
- Optional scheduling: "Leave blank for immediate start" / "Leave blank for no expiry" hints
- Null dates displayed as "Always" (validFrom) or "No end" (validUntil)
- Create/Edit dialog with title, body textarea, optional datetime-local scheduling, shop dropdown
- Same status badge system shared via `statusBadgeClass()` helper
- Delete confirmation, empty state, toast notifications

## Deviations from Plan

### Minor: Tasks 2 and 3 Combined in Single Commit
- **Reason:** Both tabs share the same file, state (shops dropdown), and helper functions. Implementing them separately would require artificial code splitting and a second edit pass.
- **Impact:** None -- all acceptance criteria for both tasks are met. Task 1 has its own dedicated commit.

## Decisions Made

1. **Shared status helpers:** Extracted `statusBadgeClass()` and `statusLabel()` as shared functions used by both promotions and announcements tabs.
2. **Lazy-load announcements:** Announcements are only fetched when the Announcements tab is first activated, reducing initial load.
3. **Separate form instances:** Used two independent `useForm` instances (promoForm, annForm) to avoid state conflicts between tabs.

## Known Stubs

None -- all CRUD operations are wired to backend API endpoints. No placeholder data or TODO markers.

## Self-Check: PASSED

- All 3 created/modified files exist on disk
- Both commit hashes (e864454, 4e6b09f) found in git log
- All acceptance criteria verified via grep checks

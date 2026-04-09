# Phase 4: Vendor Dashboard UI - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Dashboard pages for vendors to manage promotions and announcements. CRUD forms with scheduling support, status indicators, and filtering. Uses Phase 3 backend APIs.

</domain>

<decisions>
## Implementation Decisions

### Page Structure
- **D-01:** Single `/dashboard/marketing` page with two tabs: "Promotions" and "Announcements". Rationale: marketing is one domain — combining keeps context together and reduces navigation. Different from the one-page-per-entity pattern, but justified because promotions and announcements are both marketing tools for the same shop.
- **D-02:** Add "Marketing" link to dashboard sidebar navigation (in `layout.tsx`).

### Scheduling UX
- **D-03:** Use native HTML5 `<input type="datetime-local">` for validFrom/validUntil. Rationale: no extra dependency needed (avoids adding react-day-picker), browser-native datetime picker is sufficient for vendor use, and the existing dashboard has no date pickers to maintain consistency with.
- **D-04:** Display dates in user's local timezone. Send ISO 8601 with offset to backend (OffsetDateTime). Show relative status ("Starts in 2 days", "Expires tomorrow") alongside absolute dates.

### Status Indicators
- **D-05:** Derive status client-side from `validFrom`/`validUntil`/`active`:
  - **Active** (green badge): `active=true AND validFrom <= now AND validUntil >= now`
  - **Upcoming** (yellow badge): `active=true AND validFrom > now`
  - **Expired** (grey badge): `validUntil < now`
  - **Disabled** (red badge): `active=false`
- **D-06:** Filter tabs at top of each tab panel: All / Active / Upcoming / Expired. Client-side filtering (no separate API call).

### Claude's Discretion
- Table layout following existing shops/products page pattern (table component + pagination)
- Create/Edit forms in Dialog component (matching existing shop create/edit pattern)
- Discount type toggle in promotion form (PERCENTAGE shows % input, FLAT_AMOUNT shows £ input)
- Delete confirmation dialog matching existing pattern
- Toast notifications on CRUD success/error
- Empty state for new vendors with no promotions/announcements

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Template Pages (follow these patterns)
- `frontend/app/dashboard/shops/page.tsx` — CRUD page with table, dialog forms, search, pagination
- `frontend/app/dashboard/products/page.tsx` — Similar pattern with image handling
- `frontend/app/dashboard/layout.tsx` — Sidebar navigation (add Marketing link)

### UI Components (reuse these)
- `frontend/components/ui/table.tsx` — Table component
- `frontend/components/ui/dialog.tsx` — Modal dialog for forms
- `frontend/components/ui/badge.tsx` — Status badges (Active/Upcoming/Expired)
- `frontend/components/ui/pagination.tsx` — Pagination
- `frontend/components/ui/button.tsx`, `input.tsx`, `label.tsx`, `select.tsx` — Form controls
- `frontend/components/ui/toast.tsx` + `toaster.tsx` — Notifications

### API Client
- `frontend/lib/api-client.ts` — Authenticated API client (uses /api/v1/ prefix)

### Backend APIs (from Phase 3)
- `GET /api/v1/promotions` — List promotions (paginated)
- `POST /api/v1/promotions` — Create promotion
- `PUT /api/v1/promotions/{id}` — Update promotion
- `DELETE /api/v1/promotions/{id}` — Delete promotion
- `GET /api/v1/announcements` — List announcements (paginated)
- `POST /api/v1/announcements` — Create announcement
- `PUT /api/v1/announcements/{id}` — Update announcement
- `DELETE /api/v1/announcements/{id}` — Delete announcement

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- All Radix UI + TailwindCSS components already available
- Shops page provides exact CRUD+table+dialog pattern to replicate
- Badge component supports custom colours via className

### Established Patterns
- Dashboard pages use `apiClient.get/post/put/delete` with error handling via toast
- Forms use React Hook Form (react-hook-form 7.69.0) with Zod validation (zod 4.2.1)
- Pagination via `?page=N&size=M` query params
- Edit mode toggles between table view and dialog form

### Integration Points
- `frontend/app/dashboard/layout.tsx` — sidebar nav links
- `frontend/lib/api-client.ts` — all API calls through authenticated client

</code_context>

<specifics>
## Specific Ideas

No specific requirements — standard dashboard CRUD following existing patterns with marketing-specific additions (tabs, scheduling, status badges).

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-vendor-dashboard-ui*
*Context gathered: 2026-04-08*

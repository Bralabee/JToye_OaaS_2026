---
status: partial
phase: 23-vendor-scoped-access-responsive-dashboard-nav
source: [23-VERIFICATION.md]
started: 2026-07-21
updated: 2026-07-21
---

## Current Test

[awaiting human testing — requires a live environment with a real Keycloak vendor login]

## Tests

### 1. MOBL-01 — dashboard nav does not overlay content at 375px (live Playwright)
expected: On a 375px viewport, the dashboard sidebar collapses to a drawer/bottom-nav (no overlay of main content), and the shop-context switcher is reachable. The geometry-measuring Playwright spec `frontend/e2e/dashboard-mobile.spec.ts` (375px describe block) passes against the running Compose stack.
why_human: The Jest-level proxy only asserts Tailwind responsive classes in jsdom — it does not measure real viewport overflow. The real spec needs a live Keycloak vendor login (`E2E_VENDOR_PASSWORD`), which was not available in the execution session, and port-3000 needs a frontend rebuild to serve the post-change image.
how_to_run: Rebuild the frontend container, export `E2E_VENDOR_PASSWORD`, then `cd frontend && npx playwright test e2e/dashboard-mobile.spec.ts`.
result: [pending]

### 2. VSA-04 — staff-management screen click-through (live)
expected: A GROUP_ADMIN at `/dashboard/staff` can list, grant, and revoke staff roles per shop; a grant immediately unlocks access; a revoke produces a 403 (RFC 7807) within the bounded SSE window; the 403 path renders the access-required state and 409 renders the last-admin/downgrade message.
why_human: Same live-Keycloak-login dependency; the automated integration proofs (StaffManagementIntegrationTest 19/19) cover the backend, but the end-to-end browser click-through was not run live this session.
how_to_run: With the stack up and vendor creds, log in as a GROUP_ADMIN and exercise grant → verify access gained → revoke → verify 403.
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps

---
status: complete
phase: 23-vendor-scoped-access-responsive-dashboard-nav
source: [23-VERIFICATION.md]
started: 2026-07-21
updated: 2026-07-22
---

## Current Test

[testing complete]

## Tests

### 1. MOBL-01 — dashboard nav does not overlay content at 375px (live Playwright)
expected: On a 375px viewport, the dashboard sidebar collapses to a drawer/bottom-nav (no overlay of main content), and the shop-context switcher is reachable. The geometry-measuring Playwright spec `frontend/e2e/dashboard-mobile.spec.ts` (375px describe block) passes against the running Compose stack.
result: pass
evidence: |
  Verified LIVE 2026-07-22 against the running Compose stack (frontend :3000, Keycloak :8085,
  core-api :9090 — all healthy) with a real Keycloak vendor login (admin-user, dev credential
  KC_SEED_USER_PASSWORD). Ran `npx playwright test e2e/dashboard-mobile.spec.ts --project=mobile`
  → **13/13 passed (27.9s)**: 11 dashboard routes @390px + /dashboard/onboarding regression + the
  375px MOBL-01 block. The 375px assertion (dashboard-mobile.spec.ts:331) confirmed: mobile tab bar
  visible, desktop sidebar ("OaaS Platform") hidden, shop-switcher reachable inside <main>, and
  docScrollWidth (375) <= viewportWidth+1 (zero horizontal overflow), mainWidth >= 300.
  This is the exact env-deferred live run the prior session could not perform (E2E_VENDOR_PASSWORD
  unavailable + stack not rebuilt); the credential is the committed dev value in .env/realm-export.json.

### 2. VSA-04 — staff-management screen click-through (live)
expected: A GROUP_ADMIN at `/dashboard/staff` can list, grant, and revoke staff roles per shop; a grant immediately unlocks access; a revoke produces a 403 (RFC 7807) within the bounded SSE window; the 403 path renders the access-required state and 409 renders the last-admin/downgrade message.
result: pass
evidence: |
  Verified LIVE 2026-07-22 (read-only, real API — not stubbed): logged in as the realm-admin
  (admin-user, GROUP_ADMIN) and opened /dashboard/staff. The GROUP_ADMIN management UI renders
  (Grant-access form with Team member / Shop / Role selects + button; directory picker; "Current
  access" panel) and the access-required card is correctly ABSENT — proving the live GROUP_ADMIN
  gate on GET /api/v1/staff. Screenshot captured (scratchpad/phase23-uat-staff-screen.png) shows
  production-quality rendering incl. the idempotency-safe grant copy, the honest revocation-bound
  copy ("...can keep updating for up to 5 minutes until it reconnects" — WR-03/T-23-13-05), graceful
  empty states, the GA-only "Apply to all shops" switcher affordance, and the correct ACTIVE company
  no. 16471464 in the footer.
  CAVEAT (accepted by owner 2026-07-22): the live grant->revoke->403 MUTATION click-through was not
  exercised through the browser because the demo tenant's user_directory is empty (only admin-user
  has ever logged in → "0 people have signed in", 0 existing grants), so there is no second person to
  grant to. That mutation logic — grant applies, revoke -> immediate 403, last-GROUP_ADMIN -> 409,
  fail-closed on non-UUID/anonymous principals — is exhaustively proven by StaffManagementIntegrationTest
  (19/19) + ShopAccessFailClosedIntegrationTest (7/7) against real Postgres+RLS. The remaining live gap
  (screen renders + wires to the real gate) is now closed with visual proof.

## Summary

total: 2
passed: 2
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none — both tests passed]

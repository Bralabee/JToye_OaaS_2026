---
status: partial
phase: 18-vendor-onboarding-first-slice
source: [18-VERIFICATION.md]
started: 2026-07-11T05:10:00Z
updated: 2026-07-11T05:10:00Z
---

## Current Test

[items 1-4 PASSED via Playwright browser run 2026-07-11 against the rebuilt stack (phase build, V43 live); item 5 awaits the developer's product decision]

## Tests

### 1. Create-application walkthrough
expected: Signed-in vendor with no onboarding sees the create form per 18-UI-SPEC (model toggle, shop select, optional companyNumber + helper text); submitting creates DRAFT and transitions to the status view.
result: PASS — real Keycloak login → /dashboard/onboarding empty state → create form → DRAFT status view rendered. Screenshots 02/03 in scratchpad uat18/. Zero CSP violations.

### 2. VERIFYING poll behaviour
expected: GET /api/v1/onboarding/me fires every ~4s while VERIFYING (Network tab), gates show "Checking…" with spinner, polling stops when the status settles (no dangling interval).
result: PASS (with note) — real gate evaluation settled to ACTION_REQUIRED within the first poll window (CH fail-closed → MANUAL_REVIEW as designed); status + 3-gate breakdown rendered; polling correctly stopped once out of VERIFYING. Sustained-poll timing remains Jest-fake-timer-proven only (real gates settle too fast locally to observe multiple cycles).

### 3. Go-live happy path + guard-veto 400
expected: Dialog copy per spec ("Go live?" / confirm "Go live" / cancel "Not yet"); confirm POSTs /go-live and renders LIVE. Forced veto → destructive toast ("Not ready to go live yet"), gate breakdown stays visible, no crash.
result: PASS — dialog copy exact ("Go live?"); forced stale-gate veto (product durability nulled post-approval) → destructive toast shown, breakdown visible, shop stayed unpublished in DB (WR-03 proven live); restored data → confirm → LIVE badge + db status=LIVE + shops.published=t; Progress timeline rendered. Screenshots r2-01..r2-04.

### 4. Entry surfaces visual fidelity
expected: /for-operators "Start your application" CTA in the existing orange treatment; sidebar "Go live" item highlights on route; dashboard banner amber (not started) / blue (in progress) / hidden (LIVE) / slate "Your storefront is not live." (terminal states, post-IN-06 fix).
result: PASS — CTA present and linked; sidebar "Go live" item present + active-highlight verified in screenshot; banner amber pre-onboarding and absent when LIVE. Terminal-state slate variant covered by Jest (not browser-driven). Mobile 390px render clean. Screenshots 09/10/12, r2-05.

### 5. auto-approve production decision (PRODUCT/OPS — needs the developer)
expected: A documented decision: either ONBOARDING_AUTO_APPROVE=true for the target environment, or an admin-approve path ships before real vendors can reach PENDING_APPROVAL (today nothing moves an onboarding out of PENDING_APPROVAL except the auto-approve recompute).
result: [pending — developer decision]

## Notes
- Known pre-existing noise (not phase-18): NextAuth emits repeated "Failed to fetch" console errors during the signin redirect dance (login succeeds); worth a follow-up.
- Dev DB restored post-UAT (UAT product deleted, onboarding rows removed, shop unpublished).

## Summary

total: 5
passed: 4
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps

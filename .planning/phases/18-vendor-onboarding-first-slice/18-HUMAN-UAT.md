---
status: partial
phase: 18-vendor-onboarding-first-slice
source: [18-VERIFICATION.md]
started: 2026-07-11T05:10:00Z
updated: 2026-07-11T05:10:00Z
---

## Current Test

[awaiting browser verification — items 1-4 executable via Playwright on the rebuilt stack; item 5 is a product/ops decision]

## Tests

### 1. Create-application walkthrough
expected: Signed-in vendor with no onboarding sees the create form per 18-UI-SPEC (model toggle, shop select, optional companyNumber + helper text); submitting creates DRAFT and transitions to the status view.
result: [pending]

### 2. VERIFYING poll behaviour
expected: GET /api/v1/onboarding/me fires every ~4s while VERIFYING (Network tab), gates show "Checking…" with spinner, polling stops when the status settles (no dangling interval).
result: [pending]

### 3. Go-live happy path + guard-veto 400
expected: Dialog copy per spec ("Go live?" / confirm "Go live" / cancel "Not yet"); confirm POSTs /go-live and renders LIVE. Forced veto → destructive toast ("Not ready to go live yet"), gate breakdown stays visible, no crash.
result: [pending]

### 4. Entry surfaces visual fidelity
expected: /for-operators "Start your application" CTA in the existing orange treatment; sidebar "Go live" item highlights on route; dashboard banner amber (not started) / blue (in progress) / hidden (LIVE) / slate "Your storefront is not live." (terminal states, post-IN-06 fix).
result: [pending]

### 5. auto-approve production decision (PRODUCT/OPS — needs the developer)
expected: A documented decision: either ONBOARDING_AUTO_APPROVE=true for the target environment, or an admin-approve path ships before real vendors can reach PENDING_APPROVAL (today nothing moves an onboarding out of PENDING_APPROVAL except the auto-approve recompute).
result: [pending]

## Summary

total: 5
passed: 0
issues: 0
pending: 5
skipped: 0
blocked: 0

## Gaps

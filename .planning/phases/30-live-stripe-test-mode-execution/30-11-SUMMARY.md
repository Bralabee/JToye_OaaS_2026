---
phase: 30-live-stripe-test-mode-execution
plan: 11
subsystem: core-java-payment
tags: [stripe, timeouts, resilience4j, issue-108, pay-03, staging, runtime-parity]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-10: Stripe timeouts, stripe-refund/stripe-lookup breakers, the failure predicate and the typed 503 on fix/108-stripe-timeouts"
provides:
  - "PR 3 (#1044) reviewed to zero admissible, squash-merged as 9d204778 and deployed to staging"
  - "Proof from the running pod that staging's Stripe client uses connectTimeoutMs=5000 readTimeoutMs=20000 maxNetworkRetries=2 (RED before, GREEN after)"
  - "Issue #108 records the Stripe half as done and the SMTP/axios/S3/email-breaker half as open"
affects: [30-12]

actuals:
  tasks: 3
  commits: 1        # this record, on feat/30-connect-webhook; the PR 3 commits are on main as 9d204778

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/30-11-SUMMARY.md
  modified:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-11-108-live.txt

requirements-completed: [PAY-03]
---

# Plan 30-11 Summary: staging's Stripe calls are bounded, proven from the pod itself

**PR #1044 is merged (9d204778) and deployed. The running core-java pod logs `Stripe client configured (key redacted) connectTimeoutMs=5000 readTimeoutMs=20000 maxNetworkRetries=2` once and the old line 0 times. Before the merge the same read gave new 0, old 1.**

## Task 1: PR 3 opened, with its fail direction measured live

- The doc counts moved from 6613 to 6636. Both count gates were rc 1 before the update and rc 0 after.
- The staging RED was read at 12:45:48Z on pod core-java-7b7b7b59d-8296h: new line 0, old line 1.

## Task 2: review and merge

- Round 1 (`/code-review 1044 --comment`) posted 9 findings and admitted none.
- The Review-Record is on 99363f07. The resolution is issuecomment-6398359210.
- Squash-merged as 9d204778 with behind=0, no active main run, and a clean squash message.

## Task 3: the delivered runtime

| Check | Result |
|---|---|
| Main run 40208207618 | success; Deploy to Staging and Staging Edge Probe success; rollback rule armed, not needed |
| Digest parity, core-java / edge-go / frontend | all MATCH the ghcr digest for 9d204778; the pre-merge pod ran a different digest (42e5f537…) |
| New line with the exact values, per Ready core-java pod | 1 of 1 pods (pods_missing_line=0); old line 0 |
| Breakers on the pod's own `/actuator/prometheus` | `stripe`, `stripe-refund`, `stripe-lookup` present, all closed |
| Issue #108 comment (issuecomment-6400501402) | attribution 0 (control 1), names the remaining half, 0 closing keywords, issue OPEN |

## Deviations

1. **The breaker state comes from Prometheus, not from the health JSON.** The plan asks to read the health JSON inside the pod. On staging `/actuator/health` returns only `{"status":"UP"}` with no components, `/actuator/health/circuitBreakers` is 404 and `/actuator/circuitbreakers` is 401. So the state was read from `resilience4j_circuitbreaker_state` on the pod's `/actuator/prometheus` (port 9091). That read also checks identity: the two new breakers exist only in the merged code.
2. **"Branch Not Behind Base" showed a stale `in_progress` on the PR.** Its check-run had concluded success in 9 s, and GitHub reported the PR as CLEAN and MERGEABLE. The merge went ahead on that, after the other 13 required checks were green.

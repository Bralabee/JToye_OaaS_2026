---
phase: 30-live-stripe-test-mode-execution
plan: 14
subsystem: stripe-connect-endpoint
tags: [stripe, connect, webhook, key-vault, openapi, metrics, pay-03, owner-checkpoint]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-12: the /webhook/connect route; 30-13: the Key Vault -> SPC -> Secret -> env chain for stripe-credentials--connect-webhook-secret"
provides:
  - "PR 4 opened as #1046 (feat/30-connect-webhook) with its precondition named in the body"
  - "Stripe TEST Connected-accounts endpoint we_1UWN2NDpS9ICNeRtxSQMsc2M at https://api-staging.olajay.co.uk/api/v1/public/payments/webhook/connect, api_version 2026-09-30.endive, enabled, livemode false, the 7 required events"
  - "Key Vault object stripe-credentials--connect-webhook-secret in jtoye-stg-kv (version 387fbbc4233945d2b31e8dffbb8f82a9), written through --set-external from stdin, never printed"
  - "core-java's identity granted Key Vault Secrets User on the new object (--assign-access: 1 created, 46 existing, 0 deferred); preflight 44 objects, 0 gaps, three Stripe objects test-mode yes"
affects: [30-15, 30-31]

estimate:
  tasks: 3
actuals:
  tasks: 3
  commits: 4        # cf633c72, 85b7bd1d, 962913cb (2026-10-25), 89e9fde3 (2026-10-30); squashed into ec73ddee by #1046
plan_head_before: 91f97f81
plan_head_after: 89e9fde3

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-14-connect-endpoint.txt
  modified:
    - CHANGELOG.md
    - HANDOFF.md

key-decisions:
  - "The endpoint was created through the Stripe CLI (profile 'staging', acct_1U0tklDpS9ICNeRt), not the Dashboard, after an account match: that profile lists exactly the platform endpoint core-java's own key returns. A second CLI profile on another account was refused for this reason."
  - "The create response's secret was piped on stdin into --set-external, so the whsec_ never reached argv, a terminal or a file."
  - "The OpenAPI RED could not happen on this tree (30-12 had already regenerated the snapshot): replaced by the stronger plant-main's-snapshot arm, rc 1, restored, rc 0."

requirements-completed: [PAY-03]   # contributes; the box stays unchecked until the positive Connect arm (30-31)

coverage:
  - id: D1
    description: "API snapshot and counts current for PR 4"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-14-connect-endpoint.txt Task 1 (OpenApiSnapshotTest rc 1 on main's planted snapshot, rc 0 restored; docs-freshness + check-doc-metrics rc 0, both rc 1 on a 6667 -> 6666 arm)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The Connect endpoint exists, speaks the SDK's API version, and its secret is where core-java can read it"
    requirement: PAY-03
    verification:
      - kind: other
        ref: "evidence/30-14-connect-endpoint.txt Task 3 (V1 api/status/livemode/events PASS, V2 platform_url_endpoints=[we_1USBcpDpS9ICNeRt3kIwcIvC] PASS with a planted-extra control, V3 connect_object=1 control_platform_object=1 PASS; RED before: no /connect endpoint and no vault object at session start)"
        status: pass
    human_judgment: true   # the owner approved and the endpoint was created in the owner's Stripe account

completed: 2026-10-30
---

# Phase 30 Plan 14: The Connect endpoint and its secret Summary

**The Connected-accounts endpoint is registered in Stripe TEST, pinned to 2026-09-30.endive, and its signing secret is in `jtoye-stg-kv` where core-java can read it. Every precondition for merging PR 4 (#1046) was verified from the system that holds it.**

This SUMMARY was written on 2026-10-31, after the fact. The work was done on 2026-10-25 (Task 1) and 2026-10-30 (Tasks 2 and 3), and the evidence file was committed with it, but the SUMMARY was not, so GSD's safe-resume gate treated 30-14 as incomplete. It is reconstructed from `evidence/30-14-connect-endpoint.txt` and the PR's commits; nothing in it was re-measured except where stated.

## Task results

**Task 1: snapshot, counts, PR open (2026-10-25).** Plan 30-12 had already regenerated `docs/api/openapi-snapshot.json` (two added paths, `/api/v1/public/payments/webhook/connect` and its alias) and the counts (6667). So the plan's RED could not occur and was replaced by a stronger arm: main's snapshot was planted, `OpenApiSnapshotTest` went rc 1, the file was restored by blob hash, rc 0. A count arm (6667 -> 6666) turned both docs gates rc 1. The read-only endpoint list showed one TEST endpoint at the platform URL (`we_1USBcpDpS9ICNeRt3kIwcIvC`), so owner step 0 had nothing to act on; a planted `we_PLANTED` proved the selection can see an extra. PR #1046 was opened with the Key Vault precondition in its body.

**Task 2: owner action (2026-10-30, owner-approved).** Created `we_1UWN2NDpS9ICNeRtxSQMsc2M` (connect=true, 2026-09-30.endive, 7 events) through the Stripe CLI and wrote its secret with `--set-external` from stdin (version 387fbbc4…, rc 0).

**Task 3: verification (2026-10-30T21:08Z-21:09Z).** All three automated checks pass, each RED at session start. `--assign-access` created one grant on the new object (0 deferred). The preflight reads 44 objects with 0 gaps and test-mode yes for all three Stripe objects.

## Deviations

1. **The SUMMARY was not committed with the plan.** Recovered on 2026-10-31 by the safe-resume gate's "close out manually" route. No work was redone.
2. **The leak-scan control "with a real value"** that Task 1 deferred to Task 3 is not recorded in this plan's evidence. It was run on 2026-10-31 against the 30-15 evidence file (rc 0 real, rc 1 with the real connect secret planted in a scratch copy), so the scan is shown to fire; the 30-14 file itself scanned rc 0 in Task 1.

## Next

30-15: merge after a same-sitting Key Vault re-read, then prove the secret is mounted and that forgeries are refused live.

## Self-Check: PASSED

- `evidence/30-14-connect-endpoint.txt` exists on main; `we_1UWN2NDpS9ICNeRtxSQMsc2M` and the three PASS lines are in it.
- PR #1046 is MERGED (ec73ddee); commits cf633c72, 85b7bd1d, 962913cb and 89e9fde3 are on `origin/feat/30-connect-webhook`.
